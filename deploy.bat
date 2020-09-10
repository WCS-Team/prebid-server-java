aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 339267708833.dkr.ecr.us-east-1.amazonaws.com
docker build -f Dockerfile -t 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.0.1 .
docker push 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.0.1
